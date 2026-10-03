using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Billing.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class FreeAccounts : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "free_accounts",
                schema: "billing",
                columns: table => new
                {
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    added_by = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    added_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_free_accounts", x => x.user_id);
                });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "free_accounts",
                schema: "billing");
        }
    }
}
