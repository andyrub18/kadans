using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Billing.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class Init : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.EnsureSchema(
                name: "billing");

            migrationBuilder.CreateTable(
                name: "subscriptions",
                schema: "billing",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    store = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    store_key = table.Column<string>(type: "character varying(1024)", maxLength: 1024, nullable: false),
                    product_id = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    state = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    expires_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    auto_renewing = table.Column<bool>(type: "boolean", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_subscriptions", x => x.id);
                });

            migrationBuilder.CreateIndex(
                name: "IX_subscriptions_store_store_key",
                schema: "billing",
                table: "subscriptions",
                columns: new[] { "store", "store_key" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_subscriptions_user_id",
                schema: "billing",
                table: "subscriptions",
                column: "user_id");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "subscriptions",
                schema: "billing");
        }
    }
}
