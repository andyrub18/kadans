using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Budget.Migrations
{
    /// <inheritdoc />
    public partial class BudgetExchangeRate : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "exchange_rates",
                schema: "budget",
                columns: table => new
                {
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    htg_per_usd = table.Column<decimal>(type: "numeric(12,4)", precision: 12, scale: 4, nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_exchange_rates", x => x.user_id);
                });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "exchange_rates",
                schema: "budget");
        }
    }
}
